package com.hienlh.ppm.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Renders a QR code on a light `#FFFFFF` card (`tailscale-sign-in-dialog.tsx:39-41`).
 */
@Composable
internal fun QrCodeBox(
    value: String,
    size: Dp = 168.dp,
    modifier: Modifier = Modifier,
) {
    val matrix = remember(value) { encodeQrBitMatrix(value) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Color.White)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val n = matrix.size
            if (n <= 0) return@Canvas
            val cellW = this.size.width / n
            val cellH = this.size.height / n
            for (r in 0 until n) {
                val row = matrix[r]
                for (c in 0 until n) {
                    if (row[c]) {
                        drawRect(
                            color = Color.Black,
                            topLeft = Offset(c * cellW, r * cellH),
                            size = Size(cellW + 0.5f, cellH + 0.5f),
                        )
                    }
                }
            }
        }
    }
}

private data class QrVersionSpec(
    val version: Int,
    val ecCodewordsPerBlock: Int,
    val blocksG1: Int,
    val dataCwG1: Int,
    val blocksG2: Int = 0,
    val dataCwG2: Int = 0,
    val alignCenters: IntArray = intArrayOf(),
) {
    val totalDataCodewords: Int get() = blocksG1 * dataCwG1 + blocksG2 * dataCwG2
    val charCountBits: Int get() = if (version <= 9) 8 else 16
    val maxByteCount: Int get() = totalDataCodewords - (4 + charCountBits + 7) / 8
    val dimension: Int get() = 17 + version * 4
}

private val QR_LEVEL_L_SPECS = listOf(
    QrVersionSpec(1, 7, 1, 19),
    QrVersionSpec(2, 10, 1, 34, alignCenters = intArrayOf(6, 18)),
    QrVersionSpec(3, 15, 1, 55, alignCenters = intArrayOf(6, 22)),
    QrVersionSpec(4, 20, 1, 80, alignCenters = intArrayOf(6, 26)),
    QrVersionSpec(5, 26, 1, 108, alignCenters = intArrayOf(6, 30)),
    QrVersionSpec(6, 18, 2, 68, alignCenters = intArrayOf(6, 34)),
    QrVersionSpec(7, 20, 2, 78, alignCenters = intArrayOf(6, 22, 38)),
    QrVersionSpec(8, 24, 2, 97, alignCenters = intArrayOf(6, 24, 42)),
    QrVersionSpec(9, 30, 2, 116, alignCenters = intArrayOf(6, 26, 46)),
    QrVersionSpec(10, 18, 2, 68, 2, 69, alignCenters = intArrayOf(6, 28, 50)),
)

/**
 * ISO/IEC 18004 QR Code encoder (Byte mode, Error Correction Level L, Versions 1–10).
 */
internal fun encodeQrBitMatrix(text: String): Array<BooleanArray> {
    val rawBytes = text.toByteArray(Charsets.UTF_8)
    val payload = if (rawBytes.size <= QR_LEVEL_L_SPECS.last().maxByteCount) {
        rawBytes
    } else {
        rawBytes.copyOf(QR_LEVEL_L_SPECS.last().maxByteCount)
    }
    val spec = QR_LEVEL_L_SPECS.first { payload.size <= it.maxByteCount }
    val dataCodewords = buildDataCodewords(payload, spec)
    val finalCodewords = interleaveWithEc(dataCodewords, spec)

    val n = spec.dimension
    val modules = Array(n) { BooleanArray(n) }
    val isFunction = Array(n) { BooleanArray(n) }

    // 1. Finder patterns + separators
    placeFinderPattern(modules, isFunction, 0, 0)
    placeFinderPattern(modules, isFunction, 0, n - 7)
    placeFinderPattern(modules, isFunction, n - 7, 0)

    // 2. Timing patterns
    for (i in 8 until n - 8) {
        val dark = i % 2 == 0
        modules[6][i] = dark
        isFunction[6][i] = true
        modules[i][6] = dark
        isFunction[i][6] = true
    }

    // 3. Alignment patterns
    val centers = spec.alignCenters
    for (r in centers) {
        for (c in centers) {
            if ((r == 6 && c == 6) || (r == 6 && c == n - 7) || (r == n - 7 && c == 6)) continue
            placeAlignmentPattern(modules, isFunction, r, c)
        }
    }

    // 4. Dark module & reserved format/version areas
    modules[n - 8][8] = true
    isFunction[n - 8][8] = true
    reserveFormatAreas(isFunction, n)
    if (spec.version >= 7) {
        writeVersionInfo(modules, isFunction, spec.version, n)
    }

    // 5. Place data + EC bits in zigzag order and apply mask 0 ((r + c) % 2 == 0)
    placeDataBitsAndMask0(modules, isFunction, finalCodewords, n)

    // 6. Write format info for EC Level L (01) and Mask 0 (000) -> 0x77C4
    writeFormatInfo(modules, n, 0x77C4)

    return modules
}

private fun buildDataCodewords(payload: ByteArray, spec: QrVersionSpec): IntArray {
    val totalBits = spec.totalDataCodewords * 8
    val bits = BooleanArray(totalBits)
    var bitIdx = 0

    fun appendBits(value: Int, len: Int) {
        for (i in len - 1 downTo 0) {
            if (bitIdx < totalBits) {
                bits[bitIdx++] = ((value ushr i) and 1) != 0
            }
        }
    }

    // Mode indicator: Byte mode = 0100
    appendBits(0b0100, 4)
    appendBits(payload.size, spec.charCountBits)
    for (b in payload) {
        appendBits(b.toInt() and 0xFF, 8)
    }
    // Terminator (up to 4 zero bits)
    appendBits(0, minOf(4, totalBits - bitIdx))
    // Pad to byte boundary
    while (bitIdx % 8 != 0) appendBits(0, 1)
    // Pad codewords 0xEC, 0x11
    var padToggle = true
    while (bitIdx < totalBits) {
        appendBits(if (padToggle) 0xEC else 0x11, 8)
        padToggle = !padToggle
    }

    val out = IntArray(spec.totalDataCodewords)
    for (i in out.indices) {
        var acc = 0
        for (b in 0 until 8) {
            acc = (acc shl 1) or if (bits[i * 8 + b]) 1 else 0
        }
        out[i] = acc
    }
    return out
}

private fun interleaveWithEc(data: IntArray, spec: QrVersionSpec): IntArray {
    val totalBlocks = spec.blocksG1 + spec.blocksG2
    val dataBlocks = Array(totalBlocks) { IntArray(0) }
    val ecBlocks = Array(totalBlocks) { IntArray(0) }
    val gen = reedSolomonGenerator(spec.ecCodewordsPerBlock)

    var offset = 0
    for (b in 0 until totalBlocks) {
        val len = if (b < spec.blocksG1) spec.dataCwG1 else spec.dataCwG2
        val block = data.copyOfRange(offset, offset + len)
        offset += len
        dataBlocks[b] = block
        ecBlocks[b] = reedSolomonRemainder(block, gen)
    }

    val maxDataLen = maxOf(spec.dataCwG1, spec.dataCwG2)
    val result = IntArray(spec.totalDataCodewords + totalBlocks * spec.ecCodewordsPerBlock)
    var pos = 0
    for (i in 0 until maxDataLen) {
        for (b in 0 until totalBlocks) {
            if (i < dataBlocks[b].size) result[pos++] = dataBlocks[b][i]
        }
    }
    for (i in 0 until spec.ecCodewordsPerBlock) {
        for (b in 0 until totalBlocks) {
            result[pos++] = ecBlocks[b][i]
        }
    }
    return result
}

private fun gfMul(x: Int, y: Int): Int {
    var a = x
    var b = y
    var z = 0
    for (i in 7 downTo 0) {
        z = (z shl 1) xor ((z ushr 7) * 0x11D)
        z = z xor (((b ushr i) and 1) * a)
    }
    return z
}

private fun reedSolomonGenerator(degree: Int): IntArray {
    val result = IntArray(degree)
    result[degree - 1] = 1
    var root = 1
    for (i in 0 until degree) {
        for (j in 0 until degree) {
            result[j] = gfMul(result[j], root)
            if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
        }
        root = gfMul(root, 0x02)
    }
    return result
}

private fun reedSolomonRemainder(data: IntArray, divisor: IntArray): IntArray {
    val result = IntArray(divisor.size)
    for (b in data) {
        val factor = b xor result[0]
        System.arraycopy(result, 1, result, 0, result.size - 1)
        result[result.size - 1] = 0
        for (i in divisor.indices) {
            result[i] = result[i] xor gfMul(divisor[i], factor)
        }
    }
    return result
}

private fun placeFinderPattern(modules: Array<BooleanArray>, isFunction: Array<BooleanArray>, top: Int, left: Int) {
    val n = modules.size
    for (dr in -1..7) {
        for (dc in -1..7) {
            val r = top + dr
            val c = left + dc
            if (r !in 0 until n || c !in 0 until n) continue
            val inOuter = dr in 0..6 && dc in 0..6
            val dark = inOuter && (dr == 0 || dr == 6 || dc == 0 || dc == 6 || (dr in 2..4 && dc in 2..4))
            modules[r][c] = dark
            isFunction[r][c] = true
        }
    }
}

private fun placeAlignmentPattern(modules: Array<BooleanArray>, isFunction: Array<BooleanArray>, centerR: Int, centerC: Int) {
    for (dr in -2..2) {
        for (dc in -2..2) {
            val r = centerR + dr
            val c = centerC + dc
            modules[r][c] = maxOf( kotlin.math.abs(dr), kotlin.math.abs(dc) ) != 1
            isFunction[r][c] = true
        }
    }
}

private fun reserveFormatAreas(isFunction: Array<BooleanArray>, n: Int) {
    for (i in 0..8) {
        isFunction[8][i] = true
        isFunction[i][8] = true
    }
    for (i in 0..7) {
        isFunction[8][n - 1 - i] = true
        isFunction[n - 1 - i][8] = true
    }
}

private fun writeVersionInfo(modules: Array<BooleanArray>, isFunction: Array<BooleanArray>, version: Int, n: Int) {
    var rem = version
    for (i in 0 until 12) rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25)
    val bits = (version shl 12) or rem
    for (i in 0 until 18) {
        val bit = ((bits ushr i) and 1) != 0
        val a = n - 11 + i % 3
        val b = i / 3
        modules[a][b] = bit
        isFunction[a][b] = true
        modules[b][a] = bit
        isFunction[b][a] = true
    }
}

private fun placeDataBitsAndMask0(
    modules: Array<BooleanArray>,
    isFunction: Array<BooleanArray>,
    codewords: IntArray,
    n: Int,
) {
    var bitIdx = 0
    val totalBits = codewords.size * 8
    var right = n - 1
    var upward = true
    while (right >= 1) {
        if (right == 6) right = 5
        for (vert in 0 until n) {
            val r = if (upward) n - 1 - vert else vert
            for (j in 0..1) {
                val c = right - j
                if (!isFunction[r][c]) {
                    val dataBit = if (bitIdx < totalBits) {
                        ((codewords[bitIdx ushr 3] ushr (7 - (bitIdx and 7))) and 1) != 0
                    } else {
                        false
                    }
                    bitIdx++
                    val mask = (r + c) % 2 == 0
                    modules[r][c] = dataBit xor mask
                }
            }
        }
        upward = !upward
        right -= 2
    }
}

private fun writeFormatInfo(modules: Array<BooleanArray>, n: Int, formatBits: Int) {
    fun bit(i: Int): Boolean = ((formatBits ushr i) and 1) != 0
    for (i in 0..5) modules[8][i] = bit(i)
    modules[8][7] = bit(6)
    modules[8][8] = bit(7)
    modules[7][8] = bit(8)
    for (i in 9..14) modules[14 - i][8] = bit(i)

    for (i in 0..7) modules[n - 1 - i][8] = bit(i)
    for (i in 8..14) modules[8][n - 15 + i] = bit(i)
}
