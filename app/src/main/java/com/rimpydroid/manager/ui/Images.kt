package com.rimpydroid.manager.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.rimpydroid.manager.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Small in-memory image loader for preview pictures (from a file or from the Steam CDN). */
object Images {

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val failed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Steam's image server can scale pictures; the full-size preview is often several MB. */
    fun sizedUrl(url: String, size: Int): String {
        if (url.contains('?')) return url
        if (!url.contains("steamusercontent.com") && !url.contains("steamuserimages")) return url
        return "$url?imw=$size&imh=$size&ima=fit&impolicy=Letterbox&imcolor=%23000000&letterbox=false"
    }

    /** Blocking: call from a background thread. Returns null when the picture cannot be loaded. */
    fun load(url: String?, file: File?, maxSize: Int): Bitmap? {
        val key = when {
            file != null -> "f:" + file.absolutePath + ":" + file.lastModified() + ":" + maxSize
            url != null -> "u:$url:$maxSize"
            else -> return null
        }
        cache.get(key)?.let { return it }
        if (key in failed) return null
        val bytes: ByteArray? = try {
            if (file != null) {
                if (file.length() > 8L * 1024 * 1024) null else file.readBytes()
            } else {
                Http.getBytes(sizedUrl(url!!, maxSize), 20_000, 6 * 1024 * 1024)
            }
        } catch (e: Exception) {
            null
        }
        val bitmap = if (bytes == null) null else decode(bytes, maxSize)
        if (bitmap == null) {
            failed.add(key)
            return null
        }
        cache.put(key, bitmap)
        return bitmap
    }

    private fun decode(bytes: ByteArray, maxSize: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxSize && bounds.outHeight / (sample * 2) >= maxSize) sample *= 2
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Throwable) {
            null
        }
    }
}

/** A picture that loads in the background; shows an empty tile until it is there (or when it cannot be loaded). */
@Composable
fun AsyncPreview(
    url: String?,
    file: File?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    maxSize: Int = 320,
) {
    val bitmap by produceState<ImageBitmap?>(null, url, file) {
        value = null
        value = withContext(Dispatchers.IO) { Images.load(url, file, maxSize)?.asImageBitmap() }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        val b = bitmap
        if (b != null) {
            Image(bitmap = b, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        }
    }
}
