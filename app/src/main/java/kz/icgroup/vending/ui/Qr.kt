package kz.icgroup.vending.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Рисует QR-код из строки (ссылка Kaspi). */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val bitmap = remember(text) { makeQrBitmap(text) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "QR для оплаты",
            modifier = modifier,
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None, // без сглаживания: края модулей остаются чёткими
        )
    }
}

private fun makeQrBitmap(text: String): Bitmap? {
    return try {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        // Размер 0×0: ZXing вернёт матрицу «один пиксель на модуль», масштабируем сами.
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val scale = 8
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * scale * h * scale)
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        for (y in 0 until h * scale) {
            val row = y * w * scale
            for (x in 0 until w * scale) {
                pixels[row + x] = if (matrix.get(x / scale, y / scale)) black else white
            }
        }
        Bitmap.createBitmap(pixels, w * scale, h * scale, Bitmap.Config.ARGB_8888)
    } catch (e: Exception) {
        null
    }
}
