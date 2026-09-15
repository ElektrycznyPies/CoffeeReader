package com.electricdog.coffeereader.reader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.net.HttpURLConnection
import java.net.URL

fun openInBrowser(context: Context, url: String): Boolean {
    val safe = safeWebUrl(url) ?: return false
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(safe)).apply {
        // Resolve a browser rather than an installed publisher app claiming the article URL.
        selector = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try { context.startActivity(intent); true } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }
}

fun shareExport(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    context.startActivity(Intent.createChooser(intent, null))
}

fun qrBitmap(text: String): Bitmap {
    val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512)
    val pixels = IntArray(matrix.width * matrix.height) { index ->
        if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK
        else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}

object ThumbnailCache {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    fun load(address: String): Bitmap? {
        cache.get(address)?.let { return it }
        val url = safeWebUrl(address) ?: return null
        return runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.setRequestProperty("User-Agent", "CoffeeReader/0.1")
                if (connection.responseCode != 200) return null
                val data = connection.inputStream.use { readLimited(it) }
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, options)
                if (options.outWidth <= 0 || options.outHeight <= 0) return null
                options.inSampleSize = 1
                while (options.outWidth / options.inSampleSize > 320 || options.outHeight / options.inSampleSize > 320) {
                    options.inSampleSize *= 2
                }
                options.inJustDecodeBounds = false
                BitmapFactory.decodeByteArray(data, 0, data.size, options)?.also { cache.put(address, it) }
            } finally { connection.disconnect() }
        }.getOrNull()
    }
}
