package com.hienlh.ppm.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** `/api/speech/status` as a host with nothing installed answers it, and the WAV the mic posts. */
class SpeechTest {
    @Test
    fun `a status is read as the server sends it`() {
        val status = SpeechStatus.from(
            WireJson.parse(
                """
                {"installable":true,"installHint":null,"binary":null,"version":"1.9.4","model":null,"ready":false,
                 "models":[{"id":"base-q5_1","label":"Base","note":"Fastest, weakest","bytes":59707625},
                           {"id":"small-q5_1","label":"Small","note":"Middle ground","bytes":190085487}],
                 "install":null}
                """,
            ),
        )!!
        assertEquals(false, status.ready)
        assertEquals(true, status.installable)
        assertNull(status.binarySource)
        assertNull(status.model)
        assertEquals(listOf("base-q5_1", "small-q5_1"), status.models.map { it.id })
        assertEquals(59_707_625L, status.models[0].bytes)
        assertNull(status.install)
        val installed = SpeechStatus.from(
            WireJson.parse(
                """
                {"installable":true,"installHint":null,"binary":{"path":"/w/whisper-cli","source":"bundled"},"version":"1.9.4",
                 "model":{"id":"base-q5_1","label":"Base","note":"","bytes":1},"ready":true,"models":[],
                 "install":{"modelId":"base-q5_1","phase":"model","receivedBytes":5,"totalBytes":8,"done":false,"error":null}}
                """,
            ),
        )!!
        assertEquals("bundled", installed.binarySource)
        assertEquals("Base", installed.model?.label)
        assertEquals("model", installed.install?.phase)
        // Math.round(5 / 8 * 100): 62.5 goes up, as JavaScript's does.
        assertEquals(63, installed.install?.percent)
        assertNull(installed.install!!.copy(totalBytes = 0).percent)
        assertNull(SpeechStatus.from(WireJson.parse("""{"installable":true}""")))
    }

    @Test
    fun `the recording goes out as a 16 kHz mono 16-bit WAV`() {
        val pcm = byteArrayOf(1, 2, 3, 4, 5, 6, 7)
        val wav = VoiceWav.fromPcm16(pcm, length = 7)
        val head = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("an odd byte is half a sample, and dropped", 36 + 6, head.getInt(4))
        assertEquals("WAVEfmt ", String(wav, 8, 8))
        assertEquals(16, head.getInt(16))
        assertEquals(1, head.getShort(20).toInt())
        assertEquals(1, head.getShort(22).toInt())
        assertEquals(16_000, head.getInt(24))
        assertEquals(32_000, head.getInt(28))
        assertEquals(2, head.getShort(32).toInt())
        assertEquals(16, head.getShort(34).toInt())
        assertEquals("data", String(wav, 36, 4))
        assertEquals(6, head.getInt(40))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), wav.copyOfRange(44, wav.size))
        assertEquals(44 + 4, VoiceWav.fromPcm16(ByteArray(10), length = 4).size)
    }
}
