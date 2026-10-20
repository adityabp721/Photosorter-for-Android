package com.adityabalagere.photosorter

import android.content.ContentResolver
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/** A photo the blur scan suggests removing. Lower [score] = blurrier. */
data class BlurItem(val photo: Photo, val score: Float, val kind: Kind) {
    enum class Kind(val label: String) { BLURRY("Blurry"), DARK("Dark or blank") }
}

/**
 * Sharpness = variance of the Laplacian (edge strength) of the photo scaled to 512 px, taken in the
 * SHARPEST of 4x4 tiles: a sharp subject against a plain sky or wall still counts as sharp. Tiles with
 * almost no contrast are ignored; a photo with no such tile at all is dark or blank (pocket shots).
 *
 * Thresholds were calibrated on real photos: sharp ones scored 1,200-12,000; the same photos with a
 * clearly visible blur scored 85-310, heavy blur under 30.
 */
object BlurScore {
    const val SIDE = 512
    private const val GRID = 4
    private const val FLAT_STD = 6f
    const val BLURRY_BELOW = 250f
    private const val DARK_MEAN = 14f

    data class Result(val sharpness: Float, val detailedTiles: Int, val mean: Float)

    /** [gray] is w*h luminance values 0-255, row-major. */
    fun measure(gray: FloatArray, w: Int, h: Int): Result {
        require(gray.size == w * h)
        var sum = 0.0
        for (v in gray) sum += v
        val mean = (sum / gray.size.coerceAtLeast(1)).toFloat()
        if (w < 3 || h < 3) return Result(0f, 0, mean)
        var best = 0.0
        var detailed = 0
        for (ty in 0 until GRID) for (tx in 0 until GRID) {
            // tile bounds, kept one pixel inside the image for the 3x3 Laplacian
            val y0 = maxOf(1, ty * h / GRID); val y1 = minOf(h - 1, (ty + 1) * h / GRID)
            val x0 = maxOf(1, tx * w / GRID); val x1 = minOf(w - 1, (tx + 1) * w / GRID)
            var n = 0; var s = 0.0; var s2 = 0.0; var l = 0.0; var l2 = 0.0
            for (y in y0 until y1) {
                val row = y * w
                for (x in x0 until x1) {
                    val c = gray[row + x]
                    val lap = gray[row + x - 1] + gray[row + x + 1] + gray[row - w + x] + gray[row + w + x] - 4 * c
                    n++; s += c; s2 += c * c; l += lap; l2 += lap * lap
                }
            }
            if (n == 0) continue
            val std = Math.sqrt(maxOf(0.0, s2 / n - (s / n) * (s / n)))
            if (std < FLAT_STD) continue
            detailed++
            best = maxOf(best, l2 / n - (l / n) * (l / n))
        }
        return Result(best.toFloat(), detailed, mean)
    }

    /** null = looks fine. */
    fun classify(r: Result): BlurItem.Kind? = when {
        r.detailedTiles == 0 || r.mean < DARK_MEAN -> BlurItem.Kind.DARK
        r.sharpness < BLURRY_BELOW -> BlurItem.Kind.BLURRY
        else -> null
    }

    /** Scales [src] so its long side is [SIDE] (or less, never up) and returns its luminance. */
    fun gray(src: Bitmap): Triple<FloatArray, Int, Int> {
        val k = minOf(1f, SIDE.toFloat() / maxOf(src.width, src.height))
        val w = maxOf(1, (src.width * k).toInt()); val h = maxOf(1, (src.height * k).toInt())
        val bmp = if (w == src.width && h == src.height) src else Bitmap.createScaledBitmap(src, w, h, true)
        try {
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            val g = FloatArray(px.size) { i ->
                val p = px[i]
                0.299f * (p shr 16 and 0xff) + 0.587f * (p shr 8 and 0xff) + 0.114f * (p and 0xff)
            }
            return Triple(g, w, h)
        } finally {
            if (bmp !== src) bmp.recycle()
        }
    }
}

/** Thumbnails smaller than this are re-decoded; photos smaller than this (stickers, icons) are skipped. */
private const val MIN_SCORED_SIDE = 448

/** Camera-type photos only: screenshots, RAW files, GIFs and PhotoSorter's own copies are skipped. */
internal fun Photo.blurCandidate(): Boolean =
    mime.lowercase().startsWith("image/") && !mime.contains("gif", true) &&
        !isScreenshot && !isRaw && !isOwnCopy

/**
 * Full-library blur scan on 4 workers, using the system's cached thumbnails. Returns the flagged
 * photos, blurriest first (empty when [stopped] turns true).
 */
suspend fun findBlurry(
    resolver: ContentResolver,
    allPhotos: List<Photo>,
    stopped: () -> Boolean,
    progress: suspend (done: Int, total: Int) -> Unit,
): List<BlurItem> = withContext(Dispatchers.IO) {
    val photos = allPhotos.filter { it.blurCandidate() }
    val found = java.util.Collections.synchronizedList(ArrayList<BlurItem>())
    val next = AtomicInteger(0)
    val done = AtomicInteger(0)
    coroutineScope {
        repeat(4) {
            launch {
                while (!stopped()) {
                    val i = next.getAndIncrement()
                    if (i >= photos.size) break
                    val p = photos[i]
                    try {
                        var bmp = DupHash.smallBitmap(resolver, p, BlurScore.SIDE)
                        // a small thumbnail (often the camera's embedded EXIF preview) hides blur and
                        // doesn't match the calibration: decode the photo itself at 512 px instead
                        if (maxOf(bmp.width, bmp.height) < MIN_SCORED_SIDE) {
                            bmp.recycle()
                            bmp = ImageTools.decodeSmall(resolver, p.uri, BlurScore.SIDE)
                        }
                        try {
                            // tiny files (stickers, icons) say nothing about focus
                            if (maxOf(bmp.width, bmp.height) >= MIN_SCORED_SIDE) {
                                val (g, w, h) = BlurScore.gray(bmp)
                                val r = BlurScore.measure(g, w, h)
                                BlurScore.classify(r)?.let { found.add(BlurItem(p, r.sharpness, it)) }
                            }
                        } finally {
                            bmp.recycle()
                        }
                    } catch (e: Exception) {
                        Log.w("Blur", "score failed", e)
                    }
                    val d = done.incrementAndGet()
                    if (d % 50 == 0) progress(d, photos.size)
                }
            }
        }
    }
    if (stopped()) return@withContext emptyList()
    progress(photos.size, photos.size)
    found.sortedWith(compareBy<BlurItem>({ it.kind != BlurItem.Kind.DARK }, { it.score }))
}
