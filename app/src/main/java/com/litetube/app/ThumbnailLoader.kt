package com.litetube.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Lightweight video thumbnails (v1.4.0, VidMate-style landing page).
 *
 * No image library: plain OkHttp + BitmapFactory, one shared in-memory
 * LRU cache capped at 4 MB, every image downsampled to ~120x90 before
 * it is kept. A full feed of thumbnails is therefore well under 1 MB of
 * bitmaps — safe on 1 GB TVs. The loader is created once in MainActivity
 * and shared by both results adapters (home/search list and related list)
 * so there is only ever one cache.
 *
 * Each load tags the ImageView with the URL it asked for; a late result
 * is only applied if the tag still matches, so a recycled row never
 * shows another video's thumbnail. A failed fetch (or an empty URL)
 * leaves the placeholder background visible — never a crash, never a
 * broken image. Fetches run on Dispatchers.IO; the bitmap is set on the
 * main thread. The caller's lifecycleScope is used, so in-flight loads
 * are cancelled when the activity dies.
 */
class ThumbnailLoader(
    private val client: OkHttpClient,
    private val scope: CoroutineScope
) {

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * Show the thumbnail for [url] in [target]. Empty URL: placeholder
     * only. Cached: shown at once. Otherwise the placeholder stays while
     * the image downloads in the background.
     */
    fun load(url: String, target: ImageView) {
        if (url.isEmpty()) {
            target.tag = null
            target.setImageDrawable(null)
            return
        }
        target.tag = url
        cache.get(url)?.let { bitmap ->
            target.setImageBitmap(bitmap)
            return
        }
        target.setImageDrawable(null)
        scope.launch(Dispatchers.IO) {
            val bitmap = fetchThumbnail(url)
            if (bitmap != null) cache.put(url, bitmap)
            withContext(Dispatchers.Main) {
                // Row recycled for another video meanwhile? Ignore.
                if (target.tag == url && bitmap != null) {
                    target.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun fetchThumbnail(url: String): Bitmap? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) LittleTube/1.0")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                decodeDownsampled(bytes)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Decode at the smallest power-of-two sample that still covers
     * 120x90. A 120x90 default.jpg decodes at sample 1; a 320x180
     * mqdefault at sample 2 — either way the kept bitmap is tiny.
     */
    private fun decodeDownsampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= THUMB_WIDTH_PX &&
            bounds.outHeight / (sample * 2) >= THUMB_HEIGHT_PX
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    companion object {
        /** Whole in-memory thumbnail cache: 4 MB, shared by both lists. */
        private const val MAX_CACHE_BYTES = 4 * 1024 * 1024
        private const val THUMB_WIDTH_PX = 120
        private const val THUMB_HEIGHT_PX = 90
    }
}
