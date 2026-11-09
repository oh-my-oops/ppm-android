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
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

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
            val w = matrix.width
            val h = matrix.height
            if (w <= 0 || h <= 0) return@Canvas
            val cellW = this.size.width / w
            val cellH = this.size.height / h
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (matrix.get(x, y)) {
                        drawRect(
                            color = Color.Black,
                            topLeft = Offset(x * cellW, y * cellH),
                            size = Size(cellW + 0.5f, cellH + 0.5f),
                        )
                    }
                }
            }
        }
    }
}

internal fun encodeQrBitMatrix(text: String): BitMatrix =
    QRCodeWriter().encode(
        text.ifEmpty { " " },
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to 0,
        ),
    )
