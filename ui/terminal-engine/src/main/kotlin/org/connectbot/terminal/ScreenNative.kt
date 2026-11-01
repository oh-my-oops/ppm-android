package org.connectbot.terminal

import java.nio.ByteBuffer

/**
 * What the emulator asks of libvterm, and the one place a test stands in for the JNI: the library cannot load on the JVM, so the
 * unit tests give [TerminalEmulatorImpl] a model of libvterm's parse and screen instead (`FakeVterm`), which calls the emulator
 * back as the shipped library does. Production uses [TerminalNativeAdapter], which only forwards; `EngineInstrumentation` runs
 * the same cases through the real library, so a model cannot drift from it unnoticed.
 */
internal interface ScreenNative : AutoCloseable {
    fun writeInput(buffer: ByteBuffer, length: Int): Int
    fun writeInput(data: ByteArray, offset: Int, length: Int): Int
    fun resize(rows: Int, cols: Int): Int
    fun dispatchKey(modifiers: Int, key: Int): Boolean
    fun dispatchCharacter(modifiers: Int, character: Int): Boolean
    fun getCells(buffer: ByteBuffer, requests: Int): Int
    fun setPaletteColors(colors: IntArray, count: Int): Int
    fun setDefaultColors(foreground: Int, background: Int): Int
    fun setBoldHighbright(enabled: Boolean): Int
    fun pasteText(data: ByteArray)
    override fun close()
}

/** The JNI, as a [ScreenNative]. */
internal class TerminalNativeAdapter(private val native: TerminalNative) : ScreenNative {
    override fun writeInput(buffer: ByteBuffer, length: Int) = native.writeInput(buffer, length)
    override fun writeInput(data: ByteArray, offset: Int, length: Int) = native.writeInput(data, offset, length)
    override fun resize(rows: Int, cols: Int) = native.resize(rows, cols)
    override fun dispatchKey(modifiers: Int, key: Int) = native.dispatchKey(modifiers, key)
    override fun dispatchCharacter(modifiers: Int, character: Int) = native.dispatchCharacter(modifiers, character)
    override fun getCells(buffer: ByteBuffer, requests: Int) = native.getCells(buffer, requests)
    override fun setPaletteColors(colors: IntArray, count: Int) = native.setPaletteColors(colors, count)
    override fun setDefaultColors(foreground: Int, background: Int) = native.setDefaultColors(foreground, background)
    override fun setBoldHighbright(enabled: Boolean) = native.setBoldHighbright(enabled)
    override fun pasteText(data: ByteArray) = native.pasteText(data)
    override fun close() = native.close()
}
