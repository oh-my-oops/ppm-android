package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement

data class MediaVideoInfo(val codec: String, val width: Int? = null, val height: Int? = null)
data class MediaAudioInfo(val codec: String)

/** `/probe` reports capabilities even when ffprobe could not identify the file. Duration is seconds. */
data class MediaProbeInfo(
    val transcodable: Boolean,
    val encoder: String? = null,
    val duration: Double? = null,
    val container: String? = null,
    val video: MediaVideoInfo? = null,
    val audio: MediaAudioInfo? = null,
) {
    companion object {
        fun from(value: JsonElement?): MediaProbeInfo? {
            fun dimension(name: String) = value["video"][name].long?.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
            return MediaProbeInfo(
                transcodable = value["transcodable"].bool ?: return null,
                encoder = value["encoder"].string,
                duration = value["duration"].double?.takeIf { it.isFinite() && it > 0 },
                container = value["container"].string,
                video = value["video"]["codec"].string?.let { MediaVideoInfo(it, dimension("width"), dimension("height")) },
                audio = value["audio"]["codec"].string?.let(::MediaAudioInfo),
            )
        }
    }
}
