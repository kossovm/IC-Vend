package kz.icgroup.vending.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private object ImageCache {
    private val cache = LruCache<String, Bitmap>(16)

    fun get(url: String?): Bitmap? = if (url == null) null else cache.get(url)

    fun load(url: String): Bitmap? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 6_000
            conn.readTimeout = 15_000
            val bytes = conn.inputStream.use { it.readBytes() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 800 || bounds.outHeight / sample > 800) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (bitmap != null) cache.put(url, bitmap)
            bitmap
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}

/** Картинка товара по ссылке. Пока грузится или если ссылки нет, показывает fallback. */
@Composable
fun RemoteImage(url: String?, modifier: Modifier = Modifier, fallback: @Composable () -> Unit) {
    var bitmap by remember(url) { mutableStateOf(ImageCache.get(url)) }
    LaunchedEffect(url) {
        if (url != null && bitmap == null) {
            bitmap = withContext(Dispatchers.IO) { ImageCache.load(url) }
        }
    }
    val ready = bitmap
    if (ready != null) {
        Image(
            bitmap = ready.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        fallback()
    }
}
