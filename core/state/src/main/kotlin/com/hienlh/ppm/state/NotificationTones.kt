package com.hienlh.ppm.state

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The three sounds the web plays for a chat that is not in front (notification-sounds.ts), as
 * 16-bit mono samples. Each tone is an oscillator whose gain starts at its level and falls
 * exponentially to 0.001 over its length (`exponentialRampToValueAtTime`); the tones of a sound
 * add up where they overlap.
 */
object NotificationTones {
    const val DONE = "done"
    const val APPROVAL = "approval_request"
    const val QUESTION = "question"

    private class Tone(val freq: Double, val seconds: Double, val start: Double, val gain: Double, val square: Boolean = false)

    private val SOUNDS = mapOf(
        // A pleasant double chime, ascending: C5, E5.
        DONE to listOf(Tone(523.0, 0.15, 0.0, 0.15), Tone(659.0, 0.2, 0.12, 0.15)),
        // An urgent two-tone alert: A5, F5, A5.
        APPROVAL to listOf(
            Tone(880.0, 0.12, 0.0, 0.18, square = true),
            Tone(698.0, 0.12, 0.15, 0.18, square = true),
            Tone(880.0, 0.15, 0.3, 0.15, square = true),
        ),
        // A soft ascending triplet: A4, C5, E5.
        QUESTION to listOf(Tone(440.0, 0.12, 0.0, 0.12), Tone(523.0, 0.12, 0.1, 0.12), Tone(659.0, 0.18, 0.2, 0.12)),
    )

    /** The level a square wave's partial sum overshoots to; Chrome scales its square back to a peak of 1. */
    private const val SQUARE_PEAK = 1.1789797

    /** The sound for [type], or null for a type the web has no sound for. */
    fun pcm(type: String, sampleRate: Int = 48_000): ShortArray? {
        val tones = SOUNDS[type] ?: return null
        val length = tones.maxOf { ((it.start + it.seconds) * sampleRate).roundToInt() }
        val mix = DoubleArray(length)
        for (tone in tones) {
            val first = (tone.start * sampleRate).roundToInt()
            val count = (tone.seconds * sampleRate).roundToInt()
            // A square wave band-limited as an oscillator's is: its odd harmonics below Nyquist.
            val harmonics = if (tone.square) (1..((sampleRate / 2.0) / tone.freq).toInt() step 2).toList() else listOf(1)
            for (i in 0 until count) {
                val t = i.toDouble() / sampleRate
                val wave = if (tone.square) {
                    harmonics.sumOf { k -> sin(2 * PI * k * tone.freq * t) / k } * (4 / PI) / SQUARE_PEAK
                } else {
                    sin(2 * PI * tone.freq * t)
                }
                val gain = tone.gain * (0.001 / tone.gain).pow(t / tone.seconds)
                mix[first + i] += wave * gain
            }
        }
        return ShortArray(length) { (mix[it].coerceIn(-1.0, 1.0) * Short.MAX_VALUE).roundToInt().toShort() }
    }
}
