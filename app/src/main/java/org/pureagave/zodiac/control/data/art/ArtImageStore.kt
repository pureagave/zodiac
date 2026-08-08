package org.pureagave.zodiac.control.data.art

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException

/**
 * Pre-rendered art images, decoded from APK assets on demand.
 *
 * The pictures are baked into the APK by `tools/prerender_art.py`, already in
 * the cockpit's phosphor treatment. That decision is what makes this class
 * small: there is no network, no download, no disk cache and no half-arrived
 * image to guard against — on the playa there is no reliable internet, and an
 * asset either exists or does not.
 *
 * Decoding is not free (480×348 ARGB is ~670 KB in memory), so results are
 * held in a small LRU. It is sized in *bytes* rather than entries because the
 * cost that matters on a 2 GB Fire is heap, not count.
 */
class ArtImageStore(
    private val assets: AssetManager,
    maxBytes: Int = DEFAULT_CACHE_BYTES,
) {
    private val cache =
        object : LruCache<String, ImageBitmap>(maxBytes) {
            override fun sizeOf(
                key: String,
                value: ImageBitmap,
            ): Int = value.width * value.height * BYTES_PER_PIXEL
        }

    /**
     * The treated image for [uid], or null when the feed had no usable
     * photograph for it. Null is a normal answer — 17 of the 2026 pieces are
     * line drawings or BM's own test records — so callers lay out without it
     * rather than showing a placeholder box.
     */
    suspend fun load(uid: String): ImageBitmap? {
        if (uid.isBlank()) return null
        cache.get(uid)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                assets.open("$ART_DIR/$uid.webp").use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()?.also { cache.put(uid, it) }
                }
            } catch (e: IOException) {
                // Missing asset is expected for skipped pieces; anything else
                // is worth a line, but never worth failing the card over.
                Timber.d("art: no image for %s (%s)", uid, e.message)
                null
            }
        }
    }

    private companion object {
        const val ART_DIR = "art"
        const val BYTES_PER_PIXEL = 4

        /** ~6 images. Enough for the current piece plus what's next on the ring. */
        const val DEFAULT_CACHE_BYTES = 4 * 1024 * 1024
    }
}
