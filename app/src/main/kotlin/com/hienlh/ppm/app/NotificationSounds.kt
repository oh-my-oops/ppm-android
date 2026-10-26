package com.hienlh.ppm.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.hienlh.ppm.state.NotificationTones

/**
 * notification-sounds.ts's `playNotificationSound`: the type's tones, played once over whatever
 * else plays. As a notification, where the web's go out as media: it keeps to the notification
 * volume and goes quiet with the phone.
 */
object NotificationSounds {
    private const val RATE = 48_000
    private val tones = HashMap<String, ShortArray>()
    private val main = Handler(Looper.getMainLooper())

    fun play(type: String) {
        val pcm = tones.getOrPut(type) { NotificationTones.pcm(type, RATE) ?: return }
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            // Let go once it has played, with room for the device to start it late.
            main.postDelayed({ track.release() }, pcm.size * 1_000L / RATE + 1_000)
        } catch (_: Exception) {
            // The web's AudioContext can be refused too: then nothing sounds, and nothing else happens.
        }
    }
}
