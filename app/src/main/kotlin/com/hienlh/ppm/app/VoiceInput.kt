package com.hienlh.ppm.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.transcribe
import com.hienlh.ppm.protocol.VoiceWav
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/** What the chat mic listens with (`VoiceEngine`), chosen in Settings → Voice for this device. */
enum class VoiceEngine(val id: String) {
    /** The web's "browser": the device's recogniser, as Chrome gives `webkitSpeechRecognition`. */
    DEVICE("browser"),

    /** Whisper on the PPM host. */
    WHISPER("whisper"),
    ;

    companion object {
        fun of(id: String?): VoiceEngine = if (id == WHISPER.id) WHISPER else DEVICE
    }
}

/**
 * The chat mic: `useVoiceInput` and `useWhisperVoiceInput` behind the one button. The device's
 * recogniser writes into the box as you speak, driven as Chrome drives it for the web; Whisper
 * records 16 kHz PCM and posts it to the host once you stop, then writes what came back. One mic
 * for the whole app: opening it for one box closes it for any other. Driven from the main thread.
 * Unlike the web's, either engine works over plain HTTP, where a browser blocks the microphone.
 */
class VoiceInput(
    context: Context,
    private val scope: CoroutineScope,
    private val api: () -> ApiClient?,
    private val toast: (ErrorToastMessage) -> Unit,
) {
    private val context = context.applicationContext
    private var session: Session? = null

    /** The box whose mic waits on the permission dialog, and the engine it asked for. */
    private var asking: Pair<ChatTab, VoiceEngine>? = null

    /** Whether the mic shows for [engine] (`voice.supported`): Whisper needs only a microphone. */
    fun supported(engine: VoiceEngine): Boolean = engine == VoiceEngine.WHISPER || SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * The mic pressed (`handleVoiceToggle`): closed if open on [tab], else opened, what it hears
     * going after what the box holds now. Nothing while Whisper still works on the last recording.
     * Without the microphone permission, [ask] shows the system's dialog, answered in [permitted].
     */
    fun toggle(tab: ChatTab, engine: VoiceEngine, ask: () -> Unit) {
        if (tab.voiceTranscribing) return
        if (tab.voiceListening) {
            stop()
            return
        }
        tab.preVoiceText = tab.draft.text.toString().trim()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            asking = tab to engine
            ask()
            return
        }
        start(tab, engine)
    }

    /** The permission dialog's answer: the mic opens, or says why it cannot. */
    fun permitted(granted: Boolean) {
        val (tab, engine) = asking ?: return
        asking = null
        if (granted) start(tab, engine) else unavailable()
    }

    /** A message sent while the mic is open (`executeSend`): what it would still write is dropped, as the box just emptied. */
    fun closeFor(tab: ChatTab) {
        if (session?.tab === tab) session?.cancel()
    }

    private fun start(tab: ChatTab, engine: VoiceEngine) {
        // Another box's mic, or this one's still finishing. A recogniser is let go of as Chrome
        // lets go of the session a new one replaces, whatever it was still to hear; a recording
        // is still written where it was made.
        when (val previous = session) {
            is DeviceSession -> previous.cancel()
            else -> previous?.stop()
        }
        session = when (engine) {
            VoiceEngine.DEVICE -> DeviceSession(tab)
            VoiceEngine.WHISPER -> WhisperSession(tab)
        }.takeIf { it.started }
    }

    private fun stop() {
        session?.stop()
    }

    private fun unavailable() {
        toast(ErrorToastMessage("Microphone unavailable", "Allow microphone access for this app."))
    }

    private abstract inner class Session(val tab: ChatTab) {
        abstract val started: Boolean

        /** Stop listening and write what was heard. */
        abstract fun stop()

        /** Stop listening and write nothing more. */
        abstract fun cancel()

        protected fun ended() {
            if (session === this) session = null
        }
    }

    /**
     * Android's recogniser as Chrome drives it (`SpeechRecognitionImpl`, continuous with interim
     * results, `vi-VN`): Google's speech service when there is one, a session kept open across
     * pauses, each utterance arriving final as it ends. The box shows every final one so far and
     * the one still being heard, as `useVoiceInput`'s `onresult` writes them; when the session
     * ends, the finals. Its errors close the mic and say nothing, as the web's do.
     */
    private inner class DeviceSession(tab: ChatTab) : Session(tab), RecognitionListener {
        private val recognizer = SpeechRecognizer.createSpeechRecognizer(context, googleRecognizer(context))
        private val finals = StringBuilder()
        private var over = false
        override var started = false

        init {
            recognizer.setRecognitionListener(this)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(EXTRA_DICTATION_MODE, true)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, CONTINUOUS_MINIMUM_MILLIS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS)
            }
            try {
                recognizer.startListening(intent)
                started = true
                tab.voiceListening = true
            } catch (_: SecurityException) {
                recognizer.destroy()
            }
        }

        /** `recognition.stop()`: the button is off at once, and what is still being heard comes in as it finishes. */
        override fun stop() {
            tab.voiceListening = false
            if (!over) recognizer.stopListening()
        }

        override fun cancel() {
            tab.voiceListening = false
            if (over) return
            over = true
            recognizer.cancel()
            recognizer.destroy()
            ended()
        }

        private fun end() {
            if (over) return
            over = true
            tab.voiceListening = false
            recognizer.destroy()
            ended()
            if (finals.isNotEmpty()) tab.voiceResult(finals.toString().trim())
        }

        private fun results(bundle: Bundle, interim: Boolean) {
            if (over) return
            var text = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            // A partial result is its stable words and the ones still being made out.
            if (interim) bundle.getStringArrayList(EXTRA_UNSTABLE_TEXT)?.firstOrNull()?.let { text += it }
            if (text.isEmpty()) return
            if (interim) {
                tab.voiceResult("$finals $text".trim())
            } else {
                // The web joins the utterances as they come; a space goes between two that would touch.
                if (finals.isNotEmpty() && !finals.last().isWhitespace() && !text.first().isWhitespace()) finals.append(' ')
                finals.append(text)
                tab.voiceResult(finals.toString().trim())
            }
        }

        // Before Android 13 an utterance that ended arrives as a partial result marked final.
        override fun onPartialResults(partialResults: Bundle) = results(partialResults, interim = !partialResults.getBoolean(EXTRA_FINAL_RESULT, false))

        override fun onSegmentResults(segmentResults: Bundle) = results(segmentResults, interim = false)

        override fun onResults(results: Bundle) {
            results(results, interim = false)
            end()
        }

        override fun onEndOfSegmentedSession() = end()

        override fun onError(error: Int) = end()

        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    /**
     * `useWhisperVoiceInput`: the microphone recorded as 16 kHz mono PCM, which is the WAV the
     * host's whisper.cpp reads once a header goes in front, posted when the mic closes. The mic is
     * let go before the wait, then "Transcribing" until the text comes; a recording dropped, or
     * one with nothing in it, posts nothing.
     */
    private inner class WhisperSession(tab: ChatTab) : Session(tab) {
        private val pcm = ByteArrayOutputStream()

        @Volatile private var recording = true
        private var dropped = false
        private var reader: Job? = null
        override var started = false

        init {
            val record = openMicrophone()
            if (record == null) {
                unavailable()
            } else {
                started = true
                tab.voiceListening = true
                reader = scope.launch(Dispatchers.IO) {
                    val chunk = ByteArray(VoiceWav.SAMPLE_RATE / 10 * 2)
                    try {
                        while (recording) {
                            val read = record.read(chunk, 0, chunk.size)
                            if (read < 0) break
                            pcm.write(chunk, 0, read)
                        }
                    } finally {
                        record.stop()
                        record.release()
                    }
                }
            }
        }

        override fun stop() {
            if (!recording) return
            recording = false
            scope.launch {
                reader?.join()
                tab.voiceListening = false
                ended()
                if (dropped || pcm.size() == 0) return@launch
                transcribe(VoiceWav.fromPcm16(pcm.toByteArray()))
            }
        }

        override fun cancel() {
            dropped = true
            stop()
        }

        private suspend fun transcribe(wav: ByteArray) {
            val api = api() ?: return
            tab.voiceTranscribing = true
            try {
                val text = api.transcribe(wav)
                if (text.isNotEmpty()) {
                    tab.voiceResult(text)
                } else {
                    toast(ErrorToastMessage("Nothing was picked up", "Speak closer to the mic, or check which input device is selected.", kind = ErrorToastMessage.Kind.INFO))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                toast(ErrorToastMessage("Could not transcribe", error.message ?: error.toString()))
            } finally {
                tab.voiceTranscribing = false
            }
        }

        // Asked for only once the permission is held.
        @SuppressLint("MissingPermission")
        private fun openMicrophone(): AudioRecord? {
            val minimum = AudioRecord.getMinBufferSize(VoiceWav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) return null
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    VoiceWav.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minimum, VoiceWav.SAMPLE_RATE * 2),
                )
            } catch (_: Exception) {
                return null
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return null
            }
            try {
                record.startRecording()
            } catch (_: IllegalStateException) {
                record.release()
                return null
            }
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                record.release()
                return null
            }
            return record
        }
    }

    private companion object {
        // The Google speech service's own extras, which Chrome sets and reads beside the public ones.
        const val EXTRA_DICTATION_MODE = "android.speech.extra.DICTATION_MODE"
        const val EXTRA_UNSTABLE_TEXT = "android.speech.extra.UNSTABLE_TEXT"
        const val EXTRA_FINAL_RESULT = "final_result"

        /** The most the service accepts (five minutes): it keeps the session open across pauses. */
        const val CONTINUOUS_MINIMUM_MILLIS = 5 * 60 * 1000

        /**
         * The recogniser Chrome picks: Speech Services by Google from Android 12, the Google app
         * before it; else whatever the system offers, where Chrome would offer no mic at all.
         */
        fun googleRecognizer(context: Context): ComponentName? {
            val (name, minimumVersion) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                "com.google.android.tts" to -1L
            } else {
                "com.google.android.googlequicksearchbox" to 300207030L
            }
            val manager = context.packageManager
            val services = manager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), PackageManager.GET_SERVICES)
            val service = services.map { it.serviceInfo }.firstOrNull { it.packageName == name } ?: return null
            if (minimumVersion >= 0) {
                val version = runCatching { PackageInfoCompat.getLongVersionCode(manager.getPackageInfo(name, 0)) }.getOrDefault(0)
                if (version < minimumVersion) return null
            }
            return ComponentName(service.packageName, service.name)
        }
    }
}
