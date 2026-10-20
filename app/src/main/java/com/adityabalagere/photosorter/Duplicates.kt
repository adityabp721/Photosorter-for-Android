package com.adityabalagere.photosorter

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Size
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * A group of photos that are copies of the same shot.
 * `exact` = byte-identical (same SHA-256). Otherwise = same perceptual hash
 * (typical for re-saved / re-cropped / edited-then-saved versions).
 * photos are ordered newest first; the first one is the suggested "keep".
 */
data class DupCluster(val exact: Boolean, val photos: List<Photo>) {
    val key: Long get() = photos.first().id
    /** bytes reclaimable if we keep only the first photo (the original / best one) */
    val reclaimableBytes: Long get() = photos.drop(1).sumOf { it.sizeBytes }
    /** e.g. a DNG next to its JPEG - people shooting RAW+JPEG want both. */
    val hasRaw: Boolean get() = photos.any { it.isRaw }
    /** Photos to pre-select for deletion: only extra byte-identical copies, never RAW files. */
    val defaultDelete: List<Photo> get() = if (exact) photos.drop(1).filter { !it.isRaw } else emptyList()
}

object DupHash {

    const val NEAR_HAMMING = 8   // bits out of 64 allowed to differ and still be "same photo"
    private const val N = 32

    fun sha256(resolver: ContentResolver, uri: Uri): String {
        val md = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(64 * 1024)
            var n = input.read(buf)
            while (n >= 0) {
                if (n > 0) md.update(buf, 0, n)
                n = input.read(buf)
            }
        } ?: throw IllegalStateException("Cannot open file")
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 64-bit DCT-based perceptual hash (aHash/pHash family), DC removed, median threshold. */
    fun perceptualHash(src: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(src, N, N, true)
        try {
            val s = FloatArray(N * N)
            for (y in 0 until N) {
                for (x in 0 until N) {
                    val px = small.getPixel(x, y)
                    s[y * N + x] = 0.299f * (px shr 16 and 0xff) +
                        0.587f * (px shr 8 and 0xff) +
                        0.114f * (px and 0xff)
                }
            }
            val f = dct2(s)
            val low = ArrayList<Float>(63)
            for (y in 0..7) for (x in 0..7) {
                if (y == 0 && x == 0) continue
                low.add(f[y * N + x])
            }
            val median = low.sorted()[low.size / 2].toDouble()
            var bits = 0L
            for (y in 0..7) for (x in 0..7) {
                if (y == 0 && x == 0) continue
                bits = (bits shl 1) or (if (f[y * N + x] > median) 1L else 0L)
            }
            return bits
        } finally {
            if (small !== src) small.recycle()
        }
    }

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** Small software bitmap for hashing: the cached system thumbnail when possible. */
    fun smallBitmap(resolver: ContentResolver, p: Photo, px: Int = 256): Bitmap {
        if (Build.VERSION.SDK_INT >= 29) {
            val thumb = runCatching { resolver.loadThumbnail(p.uri, Size(px, px), null) }.getOrNull()
            if (thumb != null) {
                // hardware bitmaps can't be read pixel by pixel
                if (thumb.config != Bitmap.Config.HARDWARE) return thumb
                val sw = thumb.copy(Bitmap.Config.ARGB_8888, false)
                thumb.recycle()
                if (sw != null) return sw
            }
        }
        return ImageTools.decodeSmall(resolver, p.uri, px)
    }

    private val COS: Array<FloatArray> by lazy {
        Array(N) { coef -> FloatArray(N) { i ->
            val c = Math.cos(Math.PI * (2 * coef + 1) * i / N)
            if (coef == 0) (c / Math.sqrt(2.0)).toFloat() else c.toFloat()
        } }
    }

    private fun dct2(s: FloatArray): FloatArray {
        val m = FloatArray(N * N)
        for (i in 0 until N) {
            for (k in 0 until N) {
                var sum = 0f
                for (j in 0 until N) sum += COS[k][j] * s[i * N + j]
                m[i * N + k] = sum
            }
        }
        val f = FloatArray(N * N)
        for (k in 0 until N) {
            for (i in 0 until N) {
                var sum = 0f
                for (j in 0 until N) sum += COS[i][j] * m[j * N + k]
                f[i * N + k] = sum
            }
        }
        return f
    }
}

private val RAW_EXT = setOf("dng", "raw", "cr2", "cr3", "nef", "arw", "orf", "rw2", "raf")

val Photo.isRaw: Boolean
    get() = mime.contains("dng", true) || mime.contains("raw", true) ||
        title.substringAfterLast('.', "").lowercase() in RAW_EXT

fun humanBytes(b: Long): String = when {
    b <= 0 -> "size unknown"
    b < 1 shl 20 -> "${b / 1024} KB"
    b < (1L shl 30) -> String.format(Locale.US, "%.1f MB", b.toDouble() / (1 shl 20))
    else -> String.format(Locale.US, "%.2f GB", b.toDouble() / (1L shl 30))
}

/**
 * Full-library duplicate scan: exact SHA-256 first, then perceptual hash for the rest.
 * Returns early (empty) when [stopped] turns true. [progress] is suspend and may hop threads - AppModel uses it to update the UI.
 */
suspend fun findDuplicates(
    resolver: ContentResolver,
    allPhotos: List<Photo>,
    stopped: () -> Boolean,
    progress: suspend (done: Int, total: Int, phase: String) -> Unit,
): List<DupCluster> = withContext(Dispatchers.IO) {
    val photos = allPhotos.filter { it.mime.lowercase().startsWith("image/") }

    // ---- pass 1: byte-identical ----
    // Identical files have identical sizes, so only files sharing a size are hashed -
    // in a typical library that's a tiny fraction (was: every file, which took hours).
    val sameSize = photos.filter { it.sizeBytes > 0 }
        .groupBy { it.sizeBytes }.values.filter { it.size >= 2 }.flatten()
    val byHash = LinkedHashMap<String, MutableList<Photo>>()
    for ((i, p) in sameSize.withIndex()) {
        if (stopped()) return@withContext emptyList()
        val h = try {
            DupHash.sha256(resolver, p.uri)
        } catch (e: Exception) {
            Log.w("DupHash", "hash failed", e); null
        }
        if (h != null) {
            val list = byHash.getOrPut(h) { ArrayList() }
            if (list.none { it.id == p.id }) list.add(p)
        }
        if (i % 25 == 0) progress(i, sameSize.size, "hashing")
    }
    progress(sameSize.size, sameSize.size, "hashing")

    val exactClusters = byHash.values.filter { it.size >= 2 }.map { DupCluster(exact = true, photos = it.originalFirst()) }
    val inExact = exactClusters.flatMap { it.photos }.mapTo(HashSet()) { it.id }

    // ---- pass 2: perceptual (re-saves, crops, mild edits) ----
    // Hashes are computed in parallel from the system's cached thumbnails (much faster than
    // decoding full photos), then clustered in library order.
    val candidates = photos.filter { it.id !in inExact }
    val hashes = LongArray(candidates.size)
    val ok = BooleanArray(candidates.size)
    val next = AtomicInteger(0)
    val done = AtomicInteger(0)
    coroutineScope {
        repeat(4) {
            launch {
                while (!stopped()) {
                    val i = next.getAndIncrement()
                    if (i >= candidates.size) break
                    try {
                        val bmp = DupHash.smallBitmap(resolver, candidates[i])
                        try {
                            hashes[i] = DupHash.perceptualHash(bmp)
                            ok[i] = true
                        } finally {
                            bmp.recycle()
                        }
                    } catch (e: Exception) {
                        Log.w("DupHash", "phash failed", e)
                    }
                    val d = done.incrementAndGet()
                    if (d % 50 == 0) progress(d, candidates.size, "comparing")
                }
            }
        }
    }
    if (stopped()) return@withContext emptyList()
    progress(candidates.size, candidates.size, "comparing")

    val leaders = ArrayList<Pair<Long, Int>>()      // (hash, index into near)
    val near = ArrayList<MutableList<Photo>>()
    for ((i, p) in candidates.withIndex()) {
        if (!ok[i]) continue
        val h = hashes[i]
        val idx = leaders.indexOfFirst { DupHash.hamming(it.first, h) <= DupHash.NEAR_HAMMING }
        if (idx < 0) {
            leaders.add(h to near.size)
            near.add(mutableListOf(p))
        } else {
            near[leaders[idx].second].add(p)
        }
    }

    val nearClusters = near
        .mapNotNull { members ->
            if (members.size >= 2) DupCluster(false, members.bestFirst()) else null
        }

    (exactClusters + nearClusters)
        .sortedByDescending { it.reclaimableBytes }
}

/**
 * Exact copies: the ORIGINAL is kept - a file outside PhotoSorter's own folder, the one that entered
 * the library first. (Keeping the "newest" once kept app-made copies and deleted camera originals,
 * because the copies looked newer.)
 */
internal fun List<Photo>.originalFirst(): List<Photo> =
    sortedWith(compareBy<Photo>({ it.isOwnCopy }, { it.addedMs }, { it.id }))

/** Look-alikes (bursts, edits): keep the largest file outside PhotoSorter's folder - usually the best quality. */
internal fun List<Photo>.bestFirst(): List<Photo> =
    sortedWith(compareBy<Photo>({ it.isOwnCopy }, { -it.sizeBytes }, { -it.dateTakenMs }))

/**
 * Deletes the given photos via MediaStore. On Android 14+ the system shows its own
 * per-photo delete confirmation popup, so this is user-confirmed by the OS as well.
 * Returns (deletedCount, freedBytes).
 */
suspend fun deletePhotos(resolver: ContentResolver, photos: List<Photo>): Pair<Int, Long> = withContext(Dispatchers.IO) {
    var ok = 0
    var freed = 0L
    for (p in photos.sortedByDescending { it.sizeBytes }) {
        val n = runCatching { resolver.delete(p.uri, null, null) }.getOrDefault(-1)
        if (n > 0) {
            ok++
            freed += p.sizeBytes
        }
    }
    ok to freed
}
