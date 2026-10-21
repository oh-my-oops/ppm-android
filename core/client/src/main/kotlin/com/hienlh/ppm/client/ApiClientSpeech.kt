package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.SpeechStatus
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** `GET /api/speech/status`: whether Whisper is installed on the host (routes/speech.ts). */
suspend fun ApiClient.speechStatus(): SpeechStatus =
    SpeechStatus.from(call("GET", "/api/speech/status")) ?: throw PpmError.BadResponse(ApiClient.NOT_PPM)

/** `POST /api/speech/install {model}`: starts downloading Whisper and [model], which [speechStatus] then follows. */
suspend fun ApiClient.installSpeech(model: String) {
    call("POST", "/api/speech/install", body = buildJsonObject { put("model", model) })
}

/** `POST /api/speech/uninstall`. */
suspend fun ApiClient.uninstallSpeech() {
    call("POST", "/api/speech/uninstall")
}

/**
 * `POST /api/speech/transcribe?lang=` with [wav] as the body, as `postForTranscript` sends it:
 * what Whisper on the host heard, empty when it heard no speech.
 */
suspend fun ApiClient.transcribe(wav: ByteArray, language: String = "vi"): String {
    val request = request("POST", "/api/speech/transcribe", listOf("lang" to language), wav.toRequestBody("audio/wav".toMediaType()))
    return send(PpmHttp.client, request)["text"].string.orEmpty()
}
